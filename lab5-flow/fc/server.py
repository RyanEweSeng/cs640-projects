#!/usr/bin/python3

from argparse import ArgumentParser
import logging
import swp

def main():
    # Parse arguments
    arg_parser = ArgumentParser(description='Server', add_help=False)
    arg_parser.add_argument('-p', '--port', dest='port', action='store',
            type=int, required=True, help='Local port')
    arg_parser.add_argument('-h', '--hostname', dest='hostname', action='store',
            type=str, default='', help='Local hostname')
    arg_parser.add_argument('-l', '--loss', dest='loss_probability', 
            action='store', type=float, default=0.0, help='Loss probability')
    settings = arg_parser.parse_args()

    logging.basicConfig(level=logging.DEBUG, 
            format='%(levelname)s: %(message)s')

    receiver = swp.SWPReceiver((settings.hostname, settings.port), 
            settings.loss_probability)
    while True:
        data = receiver.recv().decode()
        print('%s' % data)

if __name__ == '__main__':
    main()
